package pe.edu.unmsm.fisi.retail.model;
import jakarta.persistence.*;
@Entity @Table(name="RET_SECUENCIA_CONTINGENCIA_LOCAL")
public class SecuenciaContingencia {
    @Id @GeneratedValue(strategy=GenerationType.SEQUENCE,generator="contingencia")
    @SequenceGenerator(name="contingencia",sequenceName="ret_contingencia_comprobante_seq",allocationSize=1)
    private Long id;
}
